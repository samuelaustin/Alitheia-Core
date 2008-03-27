#ifndef WCMETRIC_H
#define WCMETRIC_H

#include <Metric>
#include <Logger>
#include <Database>

class WcMetric : public Alitheia::ProjectFileMetric
{
public:
    WcMetric();

    bool install();
    std::string name() const;
    std::string author() const;
    std::string description() const;
    std::string version() const;
    std::string result() const;
    std::string getResult( const Alitheia::ProjectFile& ) const;
    void run( Alitheia::ProjectFile& );

private:
    Alitheia::Logger logger;
};

#endif